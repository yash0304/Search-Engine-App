import streamlit as st
import bs4 #website extractor
from langchain_community.tools import WikipediaQueryRun,ArxivQueryRun,DuckDuckGoSearchRun
from langchain_experimental.tools import PythonREPLTool
from langchain_community.utilities import WikipediaAPIWrapper,ArxivAPIWrapper
from langchain_groq import ChatGroq
from langchain_core.output_parsers import StrOutputParser
from langchain.agents import create_openai_tools_agent,AgentExecutor,initialize_agent,AgentType
from langchain.callbacks import StreamlitCallbackHandler
from langchain_core.prompts import ChatPromptTemplate
import os
from dotenv import load_dotenv
from langchain import hub
from langchain_community.llms import Ollama,HuggingFaceHub
from langchain.callbacks import StdOutCallbackHandler

load_dotenv()
llm = None

#sidebar settings
st.sidebar.title("Settings")
model_choice = st.sidebar.selectbox("Choose Model:", ["Groq (Fast)", "Ollama (Free)","Hugging face (Paid)"])

#model wise llm creation
if model_choice == "Groq (Fast)":
    groq_api_key = st.sidebar.text_input("Enter GROQ API KEY",type="password")
    if groq_api_key:
         llm = ChatGroq(model="llama-3.1-8b-instant",api_key=groq_api_key,streaming=False)
    else:
        st.sidebar.warning("Please enter your GROQ API key to proceed.")
        
elif model_choice == "Hugging face (Paid)":
    hf_api_key = st.sidebar.text_input("Enter Hugging Face API KEY",type="password")
    if hf_api_key:
          llm = HuggingFaceHub(
            repo_id="tiiuae/falcon-7b-instruct",
            huggingfacehub_api_token=hf_api_key,
            model_kwargs={"temperature": 0.7, "max_length": 512}
        ) 
    else:
        st.sidebar.warning("Please enter your Hugging Face API key to proceed.")
        st.stop()

else:
     llm = Ollama(model="llama3.1:8b")
     st.sidebar.info("Ollama is free to use, no API key required.")

# Initialize tools
try:
    Wiki_wrapper = WikipediaAPIWrapper(top_k_results=2, doc_content_chars_max=250)
    arxiv_wrapper = ArxivAPIWrapper(top_k_results=2, doc_content_chars_max=250)
    wiki_response = WikipediaQueryRun(api_wrapper=Wiki_wrapper)
    arxiv_response = ArxivQueryRun(api_wrapper=arxiv_wrapper)
    search = DuckDuckGoSearchRun(name="Search")
    tools = [search, wiki_response, arxiv_response]
except Exception as e:
    st.error(f"Error initializing tools: {e}")
    st.stop()

# App title
st.title("Welcome to Search Application using LLM")

# Initialize chat history
if "messages" not in st.session_state:
    st.session_state["messages"] = [
        {"role":"assistant","content":"Hi, I am the chatbot who can search the web; how can i help you?"}
    ]

# Display chat messages
for msg in st.session_state.messages:
    st.chat_message(msg["role"]).write(msg['content'])

# Chat input
if prompt:=st.chat_input(placeholder="What is machine learning?"):
        
        
        #Creating tools & llm
        if llm is None:
            st.error("LLM is not configured properly. Please check your settings.")
            st.stop()
        st.session_state.messages.append({'role':'user','content':prompt})
        st.chat_message("user").write(prompt)
        llm = llm
        try:
             
            #creating agents
            search_agent = initialize_agent(                                        
                                            tools=tools,
                                            llm=llm,
                                            agent=AgentType.ZERO_SHOT_REACT_DESCRIPTION,
                                            verbose=True,
                                            handle_parsing_errors=True,
                                            max_execution_time=300,
                                            max_iterations=15,
                                            early_stopping_method="generate",
                                            return_intermediate_steps=True
                                            )
                                            
            with st.chat_message("assistant"):
                with st.spinner("Thinking..."):                 
                    st_cb = StreamlitCallbackHandler(st.container(), expand_new_thoughts=False)
                    
                     # Using invoke instead of run for better error handling
                    result = search_agent.invoke({
                        "input": f"Please provide a comprehensive answer to: {prompt}. Use the available tools to search for current information if needed."
                    }, {"callbacks": [st_cb]})
                    
                    # Extract the response from the result
                    if isinstance(result, dict):
                        response = result.get("output", str(result))
                    else:
                        response = str(result)

                st.session_state.messages.append({'role':'assistant','content':response})
                st.write(response)
        except Exception as e:
            st.error(f"An error occurred: {e}")
            # Add error message to chat history
            error_msg = f"Sorry, I encountered an error: {str(e)}"
            st.session_state.messages.append({'role': 'assistant', 'content': error_msg})
            st.write(error_msg)

